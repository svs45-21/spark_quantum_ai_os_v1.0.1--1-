/* Program 1(a) : List ADT using Arrays
   Compiler    : Turbo C++ 3.0                                     */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#define MAX 20

class arrayadt
{
 public:
   int i,size,p,e,pos,b[MAX],x;

   arrayadt()
   {
     size=0;
   }

   void create()
   {
     cout<<"Creating a list\n";
     cout<<"Enter the size of the array (max "<<MAX<<"): ";
     cin>>size;
     if(size>MAX)
       size=MAX;
     if(size<0)
       size=0;
     cout<<"Enter "<<size<<" elements:\n";
     for(i=0;i<size;i++)
     {
       cin>>b[i];
     }
   }

   void Isfull()
   {
     if(size==MAX)
       cout<<"The array is full\n";
     else
       cout<<"The array is not full\n";
   }

   void Isempty()
   {
     if(size==0)
       cout<<"The array is empty\n";
     else
       cout<<"The array is not empty\n";
   }

   void Insert()
   {
     if(size==MAX)
     {
       cout<<"List is full, cannot insert\n";
       return;
     }
     cout<<"Enter the position of the array for the element to be inserted: ";
     cin>>pos;
     if(pos<0 || pos>size)
     {
       cout<<"Invalid position\n";
       return;
     }
     for(i=size;i>pos;i--)
     {
       b[i]=b[i-1];
     }
     cout<<"Enter the element to be inserted: ";
     cin>>p;
     b[pos]=p;
     size++;
     cout<<"List after insertion\n";
     for(i=0;i<size;i++)
       cout<<b[i]<<"\t";
     cout<<"\n";
   }

   void Delete()
   {
     if(size==0)
     {
       cout<<"List is empty, cannot delete\n";
       return;
     }
     cout<<"Enter the position of the array for the element to be deleted: ";
     cin>>pos;
     if(pos<0 || pos>=size)
     {
       cout<<"Invalid position\n";
       return;
     }
     for(i=pos+1;i<size;i++)
     {
       b[i-1]=b[i];
     }
     size--;
     cout<<"The list after deletion\n";
     for(i=0;i<size;i++)
       cout<<b[i]<<"\t";
     cout<<"\n";
   }

   void Find()
   {
     int found=0;
     cout<<"Enter the element to be searched: ";
     cin>>x;
     for(i=0;i<size;i++)
     {
       if(b[i]==x)
       {
         cout<<"Element is at position: "<<i<<"\n";
         found=1;
       }
     }
     if(found==0)
       cout<<"Element not found\n";
   }

   void FindKTH()
   {
     cout<<"Enter the position of the element to be searched: ";
     cin>>e;
     if(e<0 || e>=size)
       cout<<"Invalid position\n";
     else
       cout<<"The element at the given position is: "<<b[e]<<"\n";
   }

   void print()
   {
     cout<<"The final array\n";
     for(i=0;i<size;i++)
       cout<<b[i]<<"\t";
     cout<<"\n";
   }
};

void main()
{
  arrayadt obj;
  clrscr();
  obj.create();
  obj.Isfull();
  obj.Isempty();
  obj.Insert();
  obj.Delete();
  obj.Find();
  obj.FindKTH();
  obj.print();
  getch();
}
