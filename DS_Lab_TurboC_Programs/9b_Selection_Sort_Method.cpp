/* Program 9(b) : Selection Sort Method
   Compiler     : Turbo C++ 3.0                                    */
#include<iostream.h>
#include<conio.h>

void main()
{
  int tot, arr[50], i, j, temp, small, chk, index;
  clrscr();
  cout<<"Enter the Size of Array (max. 50): ";
  cin>>tot;
  if(tot>50) tot=50;
  cout<<"Enter "<<tot<<" Array Elements: ";
  for(i=0;i<tot;i++)
    cin>>arr[i];
  for(i=0;i<(tot-1);i++)
  {
    chk=0;
    small=arr[i];
    index=i;
    for(j=(i+1);j<tot;j++)
    {
      if(small>arr[j])
      {
        small=arr[j];
        chk++;
        index=j;
      }
    }
    if(chk!=0)
    {
      temp=arr[i];
      arr[i]=small;
      arr[index]=temp;
    }
  }
  cout<<"\nSorted Array is:\n";
  for(i=0;i<tot;i++)
    cout<<arr[i]<<"  ";
  cout<<endl;
  getch();
}
