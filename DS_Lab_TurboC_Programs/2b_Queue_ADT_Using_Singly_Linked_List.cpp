/* Program 2(b) : Queue ADT using Singly Linked List
   Compiler    : Turbo C++ 3.0                                     */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#include<stdlib.h>

struct node
{
  int data;
  struct node *next;
};

struct node *front=NULL;
struct node *rear=NULL;
struct node *temp;

void Insert()
{
  int val;
  cout<<"Insert the element in queue : ";
  cin>>val;
  temp=(struct node*)malloc(sizeof(struct node));
  temp->data=val;
  temp->next=NULL;
  if(rear==NULL)
  {
    front=rear=temp;
  }
  else
  {
    rear->next=temp;
    rear=temp;
  }
}

void Delete()
{
  temp=front;
  if(front==NULL)
  {
    cout<<"Underflow"<<endl;
    return;
  }
  cout<<"Element deleted from queue is : "<<front->data<<endl;
  front=front->next;
  if(front==NULL)
    rear=NULL;
  free(temp);
}

void Display()
{
  temp=front;
  if(front==NULL)
  {
    cout<<"Queue is empty"<<endl;
    return;
  }
  cout<<"Queue elements are : ";
  while(temp!=NULL)
  {
    cout<<temp->data<<" ";
    temp=temp->next;
  }
  cout<<endl;
}

void main()
{
  int ch;
  clrscr();
  cout<<"** QUEUE OPERATIONS **\n";
  cout<<"1) Insert element to queue"<<endl;
  cout<<"2) Delete element from queue"<<endl;
  cout<<"3) Display all the elements of queue"<<endl;
  cout<<"4) Exit"<<endl;
  do
  {
    cout<<"Enter your choice : ";
    cin>>ch;
    switch(ch)
    {
      case 1: Insert();
              break;
      case 2: Delete();
              break;
      case 3: Display();
              break;
      case 4: cout<<"Exit"<<endl;
              break;
      default: cout<<"Invalid choice"<<endl;
    }
  }while(ch!=4);
  getch();
}
